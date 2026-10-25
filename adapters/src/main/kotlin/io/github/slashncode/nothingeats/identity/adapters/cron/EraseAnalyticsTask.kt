package io.github.slashncode.nothingeats.identity.adapters.cron

import io.github.slashncode.nothingeats.identity.application.EraseAnalyticsData
import io.github.slashncode.nothingeats.platform.core.run.CronTask
import org.slf4j.LoggerFactory

/** `cron erase-analytics`: erases deleted accounts at the analytics vendor (record 030). */
class EraseAnalyticsTask(
    private val erase: EraseAnalyticsData,
) : CronTask {
    override val name = "erase-analytics"

    private val log = LoggerFactory.getLogger(EraseAnalyticsTask::class.java)

    override suspend fun run() {
        val run = erase()
        log.info("Analytics erasure: {} erased, {} failed, {} waiting for configuration", run.erased, run.failed, run.waiting)
    }
}
