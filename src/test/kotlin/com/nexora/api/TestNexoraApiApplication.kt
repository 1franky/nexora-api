package com.nexora.api

import org.springframework.boot.fromApplication
import org.springframework.boot.with


fun main(args: Array<String>) {
	fromApplication<NexoraApiApplication>().with(TestcontainersConfiguration::class).run(*args)
}
