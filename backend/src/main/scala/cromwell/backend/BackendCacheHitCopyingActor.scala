package cromwell.backend

import cromwell.backend.MetricableCacheCopyErrorCategory.MetricableCacheCopyErrorCategory
import cromwell.core.JobKey
import cromwell.core.simpleton.WomValueSimpleton

object BackendCacheHitCopyingActor {
  final case class CopyOutputsCommand(womValueSimpletons: Seq[WomValueSimpleton], jobDetritusFiles: Map[String, String], returnCode: Option[Int])

  final case class CopyingOutputsFailedResponse(jobKey: JobKey, cacheCopyAttempt: Int, failure: CacheCopyError)

  sealed trait CacheCopyError
  /** A cache hit copy was attempted but failed.  */
  final case class CopyAttemptError(failure: Throwable) extends CacheCopyError
  /** Copying was requested for a blacklisted cache hit, however the cache hit copying actor found the hit had already
    * been blacklisted so no novel copy attempt was made. */
  final case class BlacklistedError(failureCategory: MetricableCacheCopyErrorCategory) extends CacheCopyError
}

object MetricableCacheCopyErrorCategory {
  sealed trait MetricableCacheCopyErrorCategory {
    // The 'stripSuffix is necessary to handle scala 'case object's which get '$' added to the class name during java translation.
    override def toString: String = getClass.getSimpleName.stripSuffix("$").toLowerCase
  }
  final case object BucketBlacklisted extends MetricableCacheCopyErrorCategory
}
