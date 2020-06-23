// Copyright (c) 2020 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.ledger.participant.state.kvutils.tools

import java.io.{DataInputStream, FileInputStream}

import com.daml.ledger.participant.state.kvutils.export.LedgerDataExporter

object IntegrityCheckV2 {
  def main(args: Array[String]): Unit = {
    if (args.length != 1) {
      println("usage: integrity-check <ledger dump file>")
      println(
        s"You can produce a ledger dump on a kvutils ledger by setting ${LedgerDataExporter.EnvironmentVariableName}=/path/to/file")
      sys.exit(1)
    }

    val filename = args(0)
    println(s"Verifying integrity of $filename...")
    val ledgerDumpStream =
      new DataInputStream(new FileInputStream(filename))
    new IntegrityChecker(LogAppendingCommitStrategySupport).run(ledgerDumpStream)
    sys.exit(0)
  }
}