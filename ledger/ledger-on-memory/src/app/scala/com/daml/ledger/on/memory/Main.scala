// Copyright (c) 2020 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.ledger.on.memory

import akka.stream.Materializer
import com.daml.caching
import com.daml.ledger.participant.state.kvutils.api.{
  BatchingLedgerWriterConfig,
  KeyValueLedger,
  KeyValueParticipantState
}
import com.daml.ledger.participant.state.kvutils.app.batch.BatchingLedgerWriterConfigReader
import com.daml.ledger.participant.state.kvutils.app.batch.BatchingLedgerWriterConfigReader.optionsReader
import com.daml.ledger.participant.state.kvutils.app.{
  Config,
  LedgerFactory,
  ParticipantConfig,
  Runner
}
import com.daml.ledger.participant.state.kvutils.caching.`Message Weight`
import com.daml.ledger.validator.DefaultStateKeySerializationStrategy
import com.daml.ledger.validator.caching.CachingDamlLedgerStateReaderWithFingerprints.`Message-Fingerprint Pair Weight`
import com.daml.lf.engine.Engine
import com.daml.logging.LoggingContext
import com.daml.platform.akkastreams.dispatcher.Dispatcher
import com.daml.platform.configuration.LedgerConfiguration
import com.daml.resources.{ProgramResource, ResourceOwner}
import scopt.OptionParser

object Main {
  def main(args: Array[String]): Unit = {
    val resource = for {
      dispatcher <- dispatcherOwner
      sharedState = InMemoryState.empty
      factory = new InMemoryLedgerFactory(dispatcher, sharedState)
      runner <- new Runner("In-Memory Ledger", factory).owner(args)
    } yield runner

    new ProgramResource(resource).run()
  }

  class InMemoryLedgerFactory(dispatcher: Dispatcher[Index], state: InMemoryState)
      extends LedgerFactory[KeyValueParticipantState, ExtraConfig] {

    override final def readWriteServiceOwner(
        config: Config[ExtraConfig],
        participantConfig: ParticipantConfig,
        engine: Engine,
    )(
        implicit materializer: Materializer,
        loggingContext: LoggingContext,
    ): ResourceOwner[KeyValueParticipantState] =
      for {
        readerWriter <- owner(config, participantConfig, engine)
      } yield
        new KeyValueParticipantState(
          readerWriter,
          readerWriter,
          createMetrics(participantConfig, config),
        )

    def owner(config: Config[ExtraConfig], participantConfig: ParticipantConfig, engine: Engine)(
        implicit materializer: Materializer,
    ): ResourceOwner[KeyValueLedger] = {
      val metrics = createMetrics(participantConfig, config)
      if (config.extra.alwaysPreExecute)
        new InMemoryLedgerReaderWriter.PreExecutingOwner(
          ledgerId = config.ledgerId,
          participantId = participantConfig.participantId,
          keySerializationStrategy = DefaultStateKeySerializationStrategy,
          metrics = metrics,
          stateValueCacheForPreExecution = caching.WeightedCache.from(
            configuration = config.stateValueCache,
            metrics = metrics.daml.kvutils.submission.validator.stateValueCache,
          ),
          dispatcher = dispatcher,
          state = state,
          engine = engine,
        )
      else
        new InMemoryLedgerReaderWriter.BatchingOwner(
          ledgerId = config.ledgerId,
          batchingLedgerWriterConfig = config.extra.batchingLedgerWriterConfig,
          participantId = participantConfig.participantId,
          metrics = metrics,
          stateValueCache = caching.WeightedCache.from(
            configuration = config.stateValueCache,
            metrics = metrics.daml.kvutils.submission.validator.stateValueCache,
          ),
          dispatcher = dispatcher,
          state = state,
          engine = engine,
        )
    }

    override def ledgerConfig(config: Config[ExtraConfig]): LedgerConfiguration =
      LedgerConfiguration.defaultLocalLedger

    override val defaultExtraConfig: ExtraConfig = ExtraConfig.reasonableDefault

    override final def extraConfigParser(parser: OptionParser[Config[ExtraConfig]]): Unit = {
      parser
        .opt[BatchingLedgerWriterConfig]("batching")
        .optional()
        .text(BatchingLedgerWriterConfigReader.UsageText)
        .action {
          case (parsedBatchingConfig, config) =>
            config.copy(
              extra = config.extra.copy(
                batchingLedgerWriterConfig = parsedBatchingConfig
              )
            )
        }
      parser
        .opt[Boolean]("force-pre-execution")
        .optional()
        .text("Force pre-execution (mutually exclusive with batching)")
        .action {
          case (preExecute, config) =>
            config.copy(
              extra = config.extra.copy(
                alwaysPreExecute = preExecute
              )
            )
        }
      parser.checkConfig { config =>
        if (config.extra.alwaysPreExecute && config.extra.batchingLedgerWriterConfig.enableBatching)
          Left("Either pre-executing can be forced or batching can be enabled, but not both.")
        else
          Right(())
      }
      ()
    }
  }
}
