package io.ntole.wyr.core.data.mapper

import io.ntole.wyr.core.domain.report.ReportReason
import io.ntole.wyr.core.report.ReportReason as WireReportReason

/** A player's reason for a report, as the wire names it (CLAUDE.md §8d, *Reports*). Never `UNKNOWN`. */
internal fun ReportReason.toWire(): WireReportReason =
    when (this) {
        ReportReason.OFFENSIVE -> WireReportReason.OFFENSIVE
        ReportReason.REAL_PERSON -> WireReportReason.REAL_PERSON
        ReportReason.SPAM -> WireReportReason.SPAM
        ReportReason.NOT_A_CHOICE -> WireReportReason.NOT_A_CHOICE
        ReportReason.OTHER -> WireReportReason.OTHER
    }
