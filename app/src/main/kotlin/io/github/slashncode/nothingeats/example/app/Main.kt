package io.github.slashncode.nothingeats.example.app

import io.github.slashncode.nothingeats.platform.core.config.loadConfig
import io.github.slashncode.nothingeats.platform.http.auth.JwtAuth

/** `serve` (default), `migrate`, or `cron <task>`. */
fun main(args: Array<String>) {
    val config = loadConfig<ServiceConfig>()
    ServiceComponent::class.create(config, JwtAuth(config.jwt)).runner.run(args)
}
