package com.nexora.api.config

import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

/** Habilita @Scheduled (ver [com.nexora.api.notification.domain.NotificationScheduler]). */
@Configuration
@EnableScheduling
class SchedulingConfig
