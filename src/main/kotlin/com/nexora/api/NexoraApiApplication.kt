package com.nexora.api

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class NexoraApiApplication

fun main(args: Array<String>) {
	runApplication<NexoraApiApplication>(*args)
}
