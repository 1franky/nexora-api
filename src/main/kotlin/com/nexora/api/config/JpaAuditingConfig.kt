package com.nexora.api.config

import org.springframework.context.annotation.Configuration
import org.springframework.data.jpa.repository.config.EnableJpaAuditing

/** Habilita @CreatedDate/@LastModifiedDate (ver [com.nexora.api.common.domain.BaseEntity]). */
@Configuration
@EnableJpaAuditing
class JpaAuditingConfig
