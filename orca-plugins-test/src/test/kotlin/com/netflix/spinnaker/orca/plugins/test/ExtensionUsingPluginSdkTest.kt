/*
 * Copyright 2020 Armory, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.orca.plugins.test

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.netflix.spinnaker.kork.jedis.EmbeddedRedis
import com.netflix.spinnaker.kork.jedis.JedisClientDelegate
import com.netflix.spinnaker.kork.jedis.RedisClientDelegate
import com.netflix.spinnaker.kork.plugins.internal.PluginJar
import com.netflix.spinnaker.kork.plugins.tck.serviceFixture
import com.netflix.spinnaker.orca.Main
import com.netflix.spinnaker.orca.plugins.ExtensionUsingPluginSdk
import com.netflix.spinnaker.orca.plugins.OrcaPlugin
import dev.minutest.junit.JUnit5Minutests
import dev.minutest.rootContext
import java.io.File
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import redis.clients.jedis.Jedis
import redis.clients.jedis.util.Pool
import strikt.api.expect
import strikt.api.expectThat
import strikt.assertions.isEqualTo

class ExtensionUsingPluginSdkTest : JUnit5Minutests {
  fun tests() = rootContext<OrcaFixture> {
    serviceFixture {
      OrcaFixture()
    }

    test("can run a pipeline") {
      val response = mockMvc.post("/orchestrate") {
        contentType = MediaType.APPLICATION_JSON
        content = mapper.writeValueAsString(
          mapOf(
            "application" to "test",
            "stages" to listOf(
              mapOf(
                "refId" to "1",
                "type" to "wait",
                "waitTime" to 1
              )
            )
          )
        )
      }.andReturn().response

      expect {
        that(response.status).isEqualTo(200)
      }
      val ref = mapper.readValue<ExecutionRef>(response.contentAsString).ref

      var execution: Execution
      do {
        execution = mapper.readValue(mockMvc.get(ref).andReturn().response.contentAsString)
      } while (execution.endTime == null)

      expectThat(execution.status).isEqualTo("SUCCEEDED")
    }
  }
  data class ExecutionRef(val ref: String)
  data class Execution(val status: String, val endTime: Long?)
}

@SpringBootTest(classes = [Main::class])
@TestPropertySource(properties = ["spring.config.location=classpath:extension-using-plugin-sdk-test.yml"])
@ContextConfiguration(classes = [OrcaConfig::class])
@AutoConfigureMockMvc
class OrcaFixture {

  val mapper = jacksonObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)

  @Autowired
  lateinit var mockMvc: MockMvc

  init {
    val pluginId = "Armory.ExtensionUsingPluginSdk"
    val plugins = File("build/plugins").also {
      it.delete()
      it.mkdir()
    }

    PluginJar.Builder(plugins.toPath().resolve("$pluginId.jar"), pluginId)
      .pluginClass(OrcaPlugin::class.java.name)
      .pluginVersion("1.0.0")
      .manifestAttribute("Plugin-Requires", "orca>=0.0.0")
      .extensions(
        mutableListOf(
          ExtensionUsingPluginSdk::class.java.name
        )
      )
      .build()
  }
}

@Configuration
class OrcaConfig {
  @Bean(destroyMethod = "destroy")
  fun redisServer(): EmbeddedRedis {
    val redis = EmbeddedRedis.embed()
    redis.jedis.use { jedis -> jedis.flushAll() }
    return redis
  }

  @Bean
  fun jedisPool(redisServer: EmbeddedRedis): Pool<Jedis> {
    return redisServer.pool
  }

  @Bean
  fun redisClientDelegate(jedisPool: Pool<Jedis>): RedisClientDelegate {
    return JedisClientDelegate("primaryDefault", jedisPool)
  }
}
