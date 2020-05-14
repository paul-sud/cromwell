package cromwell.backend.standard.callcaching

import com.google.common.cache.{CacheBuilder, CacheLoader}
import cromwell.core.CacheConfig
import cromwell.services.CallCaching.CallCachingEntryId

sealed abstract class BlacklistCache(val config: CacheConfig, val group: Option[String]) {
  val bucketCache = {
    // Queries to the bucket blacklist cache return false by default (i.e. not blacklisted).
    val falseLoader = new CacheLoader[String, java.lang.Boolean]() {
      override def load(key: String): java.lang.Boolean = false
    }

    CacheBuilder.
      newBuilder().
      concurrencyLevel(config.concurrency).
      maximumSize(config.size).
      expireAfterWrite(config.ttl.length, config.ttl.unit).
      build[String, java.lang.Boolean](falseLoader)
  }

  val hitCache = {
    // Queries to the hit blacklist cache return false by default (i.e. not blacklisted).
    val falseLoader = new CacheLoader[CallCachingEntryId, java.lang.Boolean]() {
      override def load(key: CallCachingEntryId): java.lang.Boolean = false
    }

    CacheBuilder.
      newBuilder().
      concurrencyLevel(config.concurrency).
      maximumSize(config.size).
      expireAfterWrite(config.ttl.length, config.ttl.unit).
      build[CallCachingEntryId, java.lang.Boolean](falseLoader)
  }

  def isBlacklisted(hit: CallCachingEntryId): Boolean = hitCache.get(hit)

  def isBlacklisted(bucket: String): Boolean = bucketCache.get(bucket)

  def blacklistHit(hit: CallCachingEntryId): Unit = hitCache.put(hit, true)

  def blacklistBucket(bucket: String): Unit = bucketCache.put(bucket, true)
}

class RootWorkflowBlacklistCache(config: CacheConfig) extends BlacklistCache(config, group = None)
class GroupedBlacklistCache(config: CacheConfig, group: String) extends BlacklistCache(config, group = Option(group))
