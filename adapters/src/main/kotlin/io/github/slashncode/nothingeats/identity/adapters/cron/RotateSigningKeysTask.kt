package io.github.slashncode.nothingeats.identity.adapters.cron

import io.github.slashncode.nothingeats.identity.application.SigningKeys
import io.github.slashncode.nothingeats.platform.core.run.CronTask

/** `cron rotate-signing-keys`, scheduled as a Kubernetes CronJob. */
class RotateSigningKeysTask(
    private val keys: SigningKeys,
) : CronTask {
    override val name = "rotate-signing-keys"

    override suspend fun run() = keys.rotate()
}
