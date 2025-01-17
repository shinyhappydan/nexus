package ch.epfl.bluebrain.nexus.delta.sourcing.config

import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

import scala.concurrent.duration._

/**
  * The batch configuration.
  *
  * @param maxElements
  *   the maximum number of elements to take into account at once when saving the progress
  * @param maxInterval
  *   the maximum interval to wait for before saving the progress
  */
final case class BatchConfig(maxElements: Int, maxInterval: FiniteDuration)

object BatchConfig {

  val individual = BatchConfig(1, 5.millis)

  implicit final val batchConfigReader: ConfigReader[BatchConfig] =
    deriveReader[BatchConfig]
}
