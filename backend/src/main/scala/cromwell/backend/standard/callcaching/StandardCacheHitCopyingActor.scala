package cromwell.backend.standard.callcaching

import java.util.concurrent.TimeoutException

import akka.actor.{ActorRef, FSM}
import cats.data.NonEmptyList
import cats.instances.list._
import cats.instances.set._
import cats.instances.tuple._
import cats.syntax.foldable._
import cromwell.backend.BackendCacheHitCopyingActor.{CacheCopyError, CopyOutputsCommand, CopyingOutputsFailedResponse, LoggableCacheCopyError, MetricableCacheCopyError}
import cromwell.backend.BackendJobExecutionActor._
import cromwell.backend.BackendLifecycleActor.AbortJobCommand
import cromwell.backend.io.JobPaths
import cromwell.backend.standard.StandardCachingActorHelper
import cromwell.backend.standard.callcaching.StandardCacheHitCopyingActor._
import cromwell.backend.{BackendConfigurationDescriptor, BackendInitializationData, BackendJobDescriptor, MetricableCacheCopyErrorCategory}
import cromwell.core.CallOutputs
import cromwell.core.io._
import cromwell.core.logging.JobLogging
import cromwell.core.path.{Path, PathCopier}
import cromwell.core.simpleton.{WomValueBuilder, WomValueSimpleton}
import cromwell.services.CallCaching.CallCachingEntryId
import cromwell.services.instrumentation.CromwellInstrumentationActor
import wom.values.WomSingleFile

import scala.util.{Failure, Success, Try}

/**
  * Trait of parameters passed to a StandardCacheHitCopyingActor.
  */
trait StandardCacheHitCopyingActorParams {
  def jobDescriptor: BackendJobDescriptor

  def backendInitializationDataOption: Option[BackendInitializationData]

  def serviceRegistryActor: ActorRef

  def ioActor: ActorRef

  def configurationDescriptor: BackendConfigurationDescriptor

  /**
    * The number of this copy attempt (so that listeners can ignore "timeout"s from previous attempts)
    */
  def cacheCopyAttempt: Int

  def cacheHit: CallCachingEntryId

  def blacklistCache: Option[BlacklistCache]
}

/** A default implementation of the cache hit copying params. */
case class DefaultStandardCacheHitCopyingActorParams
(
  override val jobDescriptor: BackendJobDescriptor,
  override val backendInitializationDataOption: Option[BackendInitializationData],
  override val serviceRegistryActor: ActorRef,
  override val ioActor: ActorRef,
  override val configurationDescriptor: BackendConfigurationDescriptor,
  override val cacheCopyAttempt: Int,
  override val cacheHit: CallCachingEntryId,
  override val blacklistCache: Option[BlacklistCache]
) extends StandardCacheHitCopyingActorParams

object StandardCacheHitCopyingActor {
  type DetritusMap = Map[String, Path]
  type PathPair = (Path, Path)

  sealed trait StandardCacheHitCopyingActorState
  case object Idle extends StandardCacheHitCopyingActorState
  case object WaitingForIoResponses extends StandardCacheHitCopyingActorState
  case object FailedState extends StandardCacheHitCopyingActorState
  case object WaitingForOnSuccessResponse extends StandardCacheHitCopyingActorState

  // TODO: this mechanism here is very close to the one in CallCacheHashingJobActorData
  // Abstracting it might be valuable
  /**
    * The head subset of commandsToWaitFor is sent to the IoActor as a bulk.
    * When a response comes back, the corresponding command is removed from the head set.
    * When the head set is empty, it is removed and the next subset is sent, until there is no subset left.
    * If at any point a response comes back as a failure. Other responses for the current set will be awaited for
    * but subsequent sets will not be sent and the actor will send back a failure message.
    */
  case class StandardCacheHitCopyingActorData(commandsToWaitFor: List[Set[IoCommand[_]]],
                                              newJobOutputs: CallOutputs,
                                              newDetritus: DetritusMap,
                                              returnCode: Option[Int]
                                             ) {

    /**
      * Removes the command from commandsToWaitFor
      * returns a pair of the new state data and CommandSetState giving information about what to do next
      */
    def commandComplete(command: IoCommand[_]): (StandardCacheHitCopyingActorData, CommandSetState) = commandsToWaitFor match {
      // If everything was already done send back current data and AllCommandsDone
      case Nil => (this, AllCommandsDone)
      case lastSubset :: Nil =>
        val updatedSubset = lastSubset - command
        // If the last subset is now empty, we're done
        if (updatedSubset.isEmpty) (this.copy(commandsToWaitFor = List.empty), AllCommandsDone)
        // otherwise update commandsToWaitFor and keep waiting
        else (this.copy(commandsToWaitFor = List(updatedSubset)), StillWaiting)
      case currentSubset :: otherSubsets =>
        val updatedSubset = currentSubset - command
        // This subset is done but there are other ones, remove it from commandsToWaitFor and return the next round of commands
        if (updatedSubset.isEmpty) (this.copy(commandsToWaitFor = otherSubsets), NextSubSet(otherSubsets.head))
        // otherwise update the head subset and keep waiting
        else (this.copy(commandsToWaitFor = List(updatedSubset) ++ otherSubsets), StillWaiting)
    }
  }

  // Internal ADT to keep track of command set states
  private[callcaching] sealed trait CommandSetState
  private[callcaching] case object StillWaiting extends CommandSetState
  private[callcaching] case object AllCommandsDone extends CommandSetState
  private[callcaching] case class NextSubSet(commands: Set[IoCommand[_]]) extends CommandSetState
}

class DefaultStandardCacheHitCopyingActor(standardParams: StandardCacheHitCopyingActorParams) extends StandardCacheHitCopyingActor(standardParams)

/**
  * Standard implementation of a BackendCacheHitCopyingActor.
  */
abstract class StandardCacheHitCopyingActor(val standardParams: StandardCacheHitCopyingActorParams)
  extends FSM[StandardCacheHitCopyingActorState, Option[StandardCacheHitCopyingActorData]]
    with JobLogging with StandardCachingActorHelper with IoClientHelper with CromwellInstrumentationActor {

  override lazy val jobDescriptor: BackendJobDescriptor = standardParams.jobDescriptor
  override lazy val backendInitializationDataOption: Option[BackendInitializationData] = standardParams.backendInitializationDataOption
  override lazy val serviceRegistryActor: ActorRef = standardParams.serviceRegistryActor
  override lazy val configurationDescriptor: BackendConfigurationDescriptor = standardParams.configurationDescriptor
  protected val commandBuilder: IoCommandBuilder = DefaultIoCommandBuilder

  lazy val cacheCopyJobPaths = jobPaths.forCallCacheCopyAttempts
  lazy val destinationCallRootPath: Path = cacheCopyJobPaths.callRoot
  def destinationJobDetritusPaths: Map[String, Path] = cacheCopyJobPaths.detritusPaths

  lazy val ioActor = standardParams.ioActor

  startWith(Idle, None)

  context.become(ioReceive orElse receive)

  /** Override this method if you want to provide an alternative way to duplicate files than copying them. */
  protected def duplicate(copyPairs: Set[PathPair]): Option[Try[Unit]] = None

  when(Idle) {
    case Event(_: CopyOutputsCommand, None) if isSourceBlacklisted(standardParams.cacheHit) =>
      // We don't want to log this because blacklisting is a common and expected occurrence.
      failAndStop(MetricableCacheCopyError(MetricableCacheCopyErrorCategory.HitBlacklisted))

    case Event(command: CopyOutputsCommand, None) if isSourceBlacklisted(command) =>
      // We don't want to log this because blacklisting is a common and expected occurrence.
      failAndStop(MetricableCacheCopyError(MetricableCacheCopyErrorCategory.BucketBlacklisted))

    case Event(CopyOutputsCommand(simpletons, jobDetritus, returnCode), None) =>
      // Try to make a Path of the callRootPath from the detritus
      lookupSourceCallRootPath(jobDetritus) match {
        case Success(sourceCallRootPath) =>
          
          // process simpletons and detritus to get updated paths and corresponding IoCommands
          val processed = for {
            (destinationCallOutputs, simpletonIoCommands) <- processSimpletons(simpletons, sourceCallRootPath)
            (destinationDetritus, detritusIoCommands) <- processDetritus(jobDetritus)
          } yield (destinationCallOutputs, destinationDetritus, simpletonIoCommands ++ detritusIoCommands)

          processed match {
            case Success((destinationCallOutputs, destinationDetritus, detritusAndOutputsIoCommands)) =>
              duplicate(ioCommandsToCopyPairs(detritusAndOutputsIoCommands)) match {
                  // Use the duplicate override if exists
                case Some(Success(_)) => succeedAndStop(returnCode, destinationCallOutputs, destinationDetritus)
                case Some(Failure(failure)) =>
                  // Something went wrong in the custom duplication code. We consider this loggable because it's most likely a user-permission error:
                  failAndStop(LoggableCacheCopyError(failure))
                  // Otherwise send the first round of IoCommands (file outputs and detritus) if any
                case None if detritusAndOutputsIoCommands.nonEmpty =>
                    detritusAndOutputsIoCommands foreach sendIoCommand

                    // Add potential additional commands to the list
                  val additionalCommands = additionalIoCommands(sourceCallRootPath, simpletons, destinationCallOutputs, jobDetritus, destinationDetritus)
                  val allCommands = List(detritusAndOutputsIoCommands) ++ additionalCommands

                    goto(WaitingForIoResponses) using Option(StandardCacheHitCopyingActorData(allCommands, destinationCallOutputs, destinationDetritus, returnCode))
                case _ => succeedAndStop(returnCode, destinationCallOutputs, destinationDetritus)
              }

            // Something went wrong in generating duplication commands. We consider this loggable error because we don't expect this to happen:
            case Failure(failure) => failAndStop(LoggableCacheCopyError(failure))
          }

        // Something went wrong in looking up the call root... loggable because we don't expect this to happen:
        case Failure(failure) => failAndStop(LoggableCacheCopyError(failure))
      }
  }

  when(WaitingForIoResponses) {
    case Event(IoSuccess(command: IoCommand[_], _), Some(data)) =>
      val (newData, commandState) = data.commandComplete(command)

      commandState match {
        case StillWaiting => stay() using Option(newData)
        case AllCommandsDone =>
          handleWhitelistingForSuccess(command)
          succeedAndStop(newData.returnCode, newData.newJobOutputs, newData.newDetritus)
        case NextSubSet(commands) =>
          commands foreach sendIoCommand
          stay() using Option(newData)
      }
    case Event(f: IoReadForbiddenFailure[_], Some(data)) =>
      handleBlacklistingForForbidden(
        path = f.forbiddenPath,
        // Loggable because this is an attempt-specific problem:
        andThen = failAndAwaitPendingResponses(LoggableCacheCopyError(f.failure), f.command, data)
      )
    case Event(IoFailAck(command: IoCommand[_], failure), Some(data)) =>
      handleBlacklistingForGenericFailure()
      // Loggable because this is an attempt-specific problem:
      failAndAwaitPendingResponses(LoggableCacheCopyError(failure), command, data)
    // Should not be possible
    case Event(IoFailAck(_: IoCommand[_], failure), None) => failAndStop(LoggableCacheCopyError(failure))
  }

  when(FailedState) {
    case Event(f: IoReadForbiddenFailure[_], Some(data)) =>
      handleBlacklistingForForbidden(
        path = f.forbiddenPath,
        andThen = stayOrStopInFailedState(f, data)
      )
    case Event(fail: IoFailAck[_], Some(data)) =>
      // Not a forbidden failure so do not blacklist the bucket but do blacklist the hit.
      handleBlacklistingForGenericFailure()
      stayOrStopInFailedState(fail, data)
    // At this point success or failure doesn't matter, we've already failed this hit
    case Event(response: IoAck[_], Some(data)) =>
      stayOrStopInFailedState(response, data)
  }

  whenUnhandled {
    case Event(AbortJobCommand, _) =>
      abort()
    case Event(unexpected, _) =>
      log.warning(s"Backend cache hit copying actor received an unexpected message: $unexpected in state $stateName")
      stay()
  }

  private def stayOrStopInFailedState(response: IoAck[_], data: StandardCacheHitCopyingActorData): State = {
    val (newData, commandState) = data.commandComplete(response.command)
    commandState match {
      // If we're still waiting for some responses, stay
      case StillWaiting => stay() using Option(newData)
      // Otherwise we're done
      case _ =>
        context stop self
        stay()
    }
  }

  /* Blacklist by bucket and hit if appropriate. */
  private def handleBlacklistingForForbidden[T](path: String, andThen: => State): State = {
    for {
      // Blacklist the hit first in the forcomp since not all configurations will support bucket blacklisting.
      cache <- standardParams.blacklistCache
      _ = blacklistAndMetricHit(cache, standardParams.cacheHit)
      prefix <- extractBlacklistPrefix(path)
      _ = blacklistAndMetricBucket(cache, prefix)
    } yield()
    andThen
  }

  private def handleBlacklistingForGenericFailure(): Unit = {
    standardParams.blacklistCache foreach { blacklistAndMetricHit(_, standardParams.cacheHit) }
  }

  /* Whitelist by bucket and hit if appropriate. */
  private def handleWhitelistingForSuccess(command: IoCommand[_]): Unit = {
    for {
      cache <- standardParams.blacklistCache
      _ = whitelistAndMetricHit(cache, standardParams.cacheHit)
      copy <- Option(command) collect { case c: IoCopyCommand => c }
      prefix <- extractBlacklistPrefix(copy.source.toString)
      _ = whitelistAndMetricBucket(cache, prefix)
    } yield ()
    ()
  }

  private def publishBlacklistMetric(blacklistCache: BlacklistCache, verb: String, bucketOrHit: String, key: String, value: BlacklistStatus): Unit = {
    // TODO probably remove the logging after interactive testing is complete
    log.info("Publishing blacklist metric: {} {} {} {}", verb, bucketOrHit, key, value)
    // TODO don't publish write metrics when what is being written is consistent with what was already in the cache.
    val group = blacklistCache.name.getOrElse("none")
    val metricPath = NonEmptyList.of(
      "job",
      "callcaching", "blacklist", verb, bucketOrHit, jobDescriptor.taskCall.localName, group, key, value.toString)
    increment(metricPath)
  }

  private def blacklistAndMetricHit(blacklistCache: BlacklistCache, hit: CallCachingEntryId): Unit = {
    blacklistCache.blacklistHit(standardParams.cacheHit)
    publishBlacklistMetric(blacklistCache, verb = "write", bucketOrHit = "hit", hit.id.toString, value = KnownBad)
  }

  private def blacklistAndMetricBucket(blacklistCache: BlacklistCache, bucket: String): Unit = {
    blacklistCache.blacklistBucket(bucket)
    publishBlacklistMetric(blacklistCache, verb = "write", bucketOrHit = "bucket", bucket, value = KnownBad)
  }

  private def whitelistAndMetricHit(blacklistCache: BlacklistCache, hit: CallCachingEntryId): Unit = {
    blacklistCache.hitCache.get(standardParams.cacheHit) match {
      case Unknown =>
        blacklistCache.whitelistHit(hit)
        publishBlacklistMetric(blacklistCache, verb = "write", bucketOrHit = "hit", hit.id.toString, value = KnownGood)
      case KnownGood => // This hit is already known to be good, no need to rewrite or spam metrics.
      case KnownBad =>
        // This is surprising, a hit that we failed to copy before has now been the source of a successful copy.
        // Don't overwrite this to KnownGood, hopefully there are less weird cache hits out there.
        log.warning(
          "Cache hit {} found in KnownBad blacklist state, not overwriting to KnownGood despite successful copy.",
          standardParams.cacheHit.id)
    }
  }

  private def whitelistAndMetricBucket(blacklistCache: BlacklistCache, bucket: String): Unit = {
    blacklistCache.bucketCache.get(bucket) match {
      case Unknown =>
        blacklistCache.whitelistBucket(bucket)
        publishBlacklistMetric(blacklistCache, verb = "write", bucketOrHit = "bucket", bucket, value = KnownGood)
      case KnownGood => // This bucket is already known to be good, no need to rewrite or spam metrics.
      case KnownBad =>
        // This is surprising, a bucket that we failed to copy from before for auth reasons has now been the source
        // of a successful copy. Don't overwrite this to KnownGood, hopefully there are less weird cache hits out there.
        log.warning(
          "Bucket {} found in KnownBad blacklist state, not overwriting to KnownGood despite successful copy.",
          bucket)
    }
  }

  def succeedAndStop(returnCode: Option[Int], copiedJobOutputs: CallOutputs, detritusMap: DetritusMap) = {
    import cromwell.services.metadata.MetadataService.implicits.MetadataAutoPutter
    serviceRegistryActor.putMetadata(jobDescriptor.workflowDescriptor.id, Option(jobDescriptor.key), startMetadataKeyValues)
    context.parent ! JobSucceededResponse(jobDescriptor.key, returnCode, copiedJobOutputs, Option(detritusMap), Seq.empty, None, resultGenerationMode = CallCached)
    context stop self
    stay()
  }

  def failAndStop(failure: CacheCopyError): State = {
    context.parent ! CopyingOutputsFailedResponse(jobDescriptor.key, standardParams.cacheCopyAttempt, failure)
    context stop self
    stay()
  }

  /** If there are no responses pending this behaves like `failAndStop`, otherwise this goes to `FailedState` and waits
    * for all the pending responses to come back before stopping. */
  def failAndAwaitPendingResponses(failure: CacheCopyError, command: IoCommand[_], data: StandardCacheHitCopyingActorData): State = {
    context.parent ! CopyingOutputsFailedResponse(jobDescriptor.key, standardParams.cacheCopyAttempt, failure)

    val (newData, commandState) = data.commandComplete(command)
    commandState match {
      // If we're still waiting for some responses, go to failed state
      case StillWaiting => goto(FailedState) using Option(newData)
      // Otherwise we're done
      case _ =>
        context stop self
        stay()
    }
  }

  def abort() = {
    log.warning("{}: Abort not supported during cache hit copying", jobTag)
    context.parent ! JobAbortedResponse(jobDescriptor.key)
    context stop self
    stay()
  }

  protected def lookupSourceCallRootPath(sourceJobDetritusFiles: Map[String, String]): Try[Path] = {
    sourceJobDetritusFiles.get(JobPaths.CallRootPathKey) match {
      case Some(source) => getPath(source)
      case None => Failure(new RuntimeException(s"${JobPaths.CallRootPathKey} wasn't found for call ${jobDescriptor.taskCall.fullyQualifiedName}"))
    }
  }

  private def ioCommandsToCopyPairs(commands: Set[IoCommand[_]]): Set[PathPair] = commands collect {
    case copyCommand: IoCopyCommand => copyCommand.source -> copyCommand.destination
  }

  /**
    * Returns a pair of the list of simpletons with copied paths, and copy commands necessary to perform those copies. 
    */
  protected def processSimpletons(womValueSimpletons: Seq[WomValueSimpleton], sourceCallRootPath: Path): Try[(CallOutputs, Set[IoCommand[_]])] = Try {
    val (destinationSimpletons, ioCommands): (List[WomValueSimpleton], Set[IoCommand[_]]) = womValueSimpletons.toList.foldMap({
      case WomValueSimpleton(key, wdlFile: WomSingleFile) =>
        val sourcePath = getPath(wdlFile.value).get
        val destinationPath = PathCopier.getDestinationFilePath(sourceCallRootPath, sourcePath, destinationCallRootPath)

        val destinationSimpleton = WomValueSimpleton(key, WomSingleFile(destinationPath.pathAsString))

        List(destinationSimpleton) -> Set(commandBuilder.copyCommand(sourcePath, destinationPath, overwrite = true))
      case nonFileSimpleton => (List(nonFileSimpleton), Set.empty[IoCommand[_]])
    })

    (WomValueBuilder.toJobOutputs(jobDescriptor.taskCall.outputPorts, destinationSimpletons), ioCommands)
  }

  /**
    * Returns the file (and ONLY the file detritus) intersection between the cache hit and this call.
    */
  protected final def detritusFileKeys(sourceJobDetritusFiles: Map[String, String]) = {
    val sourceKeys = sourceJobDetritusFiles.keySet
    val destinationKeys = destinationJobDetritusPaths.keySet
    sourceKeys.intersect(destinationKeys).filterNot(_ == JobPaths.CallRootPathKey)
  }

  /**
    * Returns a pair of the detritus with copied paths, and copy commands necessary to perform those copies. 
    */
  protected def processDetritus(sourceJobDetritusFiles: Map[String, String]): Try[(Map[String, Path], Set[IoCommand[_]])] = Try {
    val fileKeys = detritusFileKeys(sourceJobDetritusFiles)

    val zero = (Map.empty[String, Path], Set.empty[IoCommand[_]])

    val (destinationDetritus, ioCommands) = fileKeys.foldLeft(zero)({
      case ((detrituses, commands), detritus) =>
        val sourcePath = getPath(sourceJobDetritusFiles(detritus)).get
        val destinationPath = destinationJobDetritusPaths(detritus)

        val newDetrituses = detrituses + (detritus -> destinationPath)

        (newDetrituses, commands + commandBuilder.copyCommand(sourcePath, destinationPath, overwrite = true))
    })

    (destinationDetritus + (JobPaths.CallRootPathKey -> destinationCallRootPath), ioCommands)
  }

  /**
    * Additional IoCommands that will be sent after (and only after) output and detritus commands complete successfully.
    * See StandardCacheHitCopyingActorData
    */
  protected def additionalIoCommands(sourceCallRootPath: Path,
                                     originalSimpletons: Seq[WomValueSimpleton],
                                     newOutputs: CallOutputs,
                                     originalDetritus:  Map[String, String],
                                     newDetritus: Map[String, Path]): List[Set[IoCommand[_]]] = List.empty

  override protected def onTimeout(message: Any, to: ActorRef): Unit = {
    val exceptionMessage = message match {
      case copyCommand: IoCopyCommand => s"The Cache hit copying actor timed out waiting for a response to copy ${copyCommand.source.pathAsString} to ${copyCommand.destination.pathAsString}"
      case touchCommand: IoTouchCommand => s"The Cache hit copying actor timed out waiting for a response to touch ${touchCommand.file.pathAsString}"
      case other => s"The Cache hit copying actor timed out waiting for an unknown I/O operation: $other"
    }

    // Loggable because this is attempt-specific:
    failAndStop(LoggableCacheCopyError(new TimeoutException(exceptionMessage)))
    ()
  }

  /**
    * If a subclass of this `StandardCacheHitCopyingActor` supports blacklisting by path then it should implement this
    * to return the prefix of the path from the failed copy command to use for blacklisting.
    */
  protected def extractBlacklistPrefix(path: String): Option[String] = None

  private def sourcePathFromCopyOutputsCommand(command: CopyOutputsCommand): String = command.jobDetritusFiles.values.head

  //noinspection ActorMutableStateInspection
  private var publishedBucketBlacklistRead = false

  private def isSourceBlacklisted(command: CopyOutputsCommand): Boolean = {
    val path = sourcePathFromCopyOutputsCommand(command)
    (for {
      cache <- standardParams.blacklistCache
      prefix <- extractBlacklistPrefix(path)
      value = cache.isBlacklisted(prefix)
      _ = if (!publishedBucketBlacklistRead) publishBlacklistMetric(cache, verb = "read", bucketOrHit = "bucket", prefix, value)
      _ = publishedBucketBlacklistRead = true
    } yield value == KnownBad).getOrElse(false)
  }

  //noinspection ActorMutableStateInspection
  private var publishedHitBlacklistRead = false

  private def isSourceBlacklisted(hit: CallCachingEntryId): Boolean = {
    (for {
      cache <- standardParams.blacklistCache
      value = cache.isBlacklisted(hit)
      _ = if (!publishedHitBlacklistRead) publishBlacklistMetric(cache, verb = "read", bucketOrHit = "hit", hit.id.toString, value)
      _ = publishedHitBlacklistRead = true
    } yield value == KnownBad).getOrElse(false)
  }
}
