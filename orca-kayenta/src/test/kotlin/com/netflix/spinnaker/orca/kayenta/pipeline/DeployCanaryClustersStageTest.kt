/*
 * Copyright 2018 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
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

package com.netflix.spinnaker.orca.kayenta.pipeline

import com.netflix.spinnaker.orca.clouddriver.pipeline.cluster.FindImageFromClusterStage
import com.netflix.spinnaker.orca.fixture.pipeline
import com.netflix.spinnaker.orca.fixture.stage
import com.netflix.spinnaker.orca.kato.pipeline.ParallelDeployStage
import com.netflix.spinnaker.orca.pipeline.StageDefinitionBuilder
import com.netflix.spinnaker.orca.pipeline.graph.StageGraphBuilder
import com.netflix.spinnaker.orca.pipeline.model.Stage
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.fail
import org.jetbrains.spek.api.Spek
import org.jetbrains.spek.api.dsl.describe
import org.jetbrains.spek.api.dsl.given
import org.jetbrains.spek.api.dsl.it

internal object DeployCanaryClustersStageTest : Spek({

  describe("constructing synthetic stages") {

    val subject = DeployCanaryClustersStage()

    given("a canary deployment pipeline") {
      val baseline = mapOf(
        "application" to "spindemo",
        "account" to "prod",
        "cluster" to "spindemo-prestaging-prestaging"
      )
      val controlClusterA = cluster {
        mapOf(
          "application" to "spindemo",
          "stack" to "prestaging",
          "freeFormDetails" to "baseline-a"
        )
      }
      val controlClusterB = cluster {
        mapOf(
          "application" to "spindemo",
          "stack" to "prestaging",
          "freeFormDetails" to "baseline-b"
        )
      }
      val experimentClusterA = cluster {
        mapOf(
          "application" to "spindemo",
          "stack" to "prestaging",
          "freeFormDetails" to "canary-a"
        )
      }
      val experimentClusterB = cluster {
        mapOf(
          "application" to "spindemo",
          "stack" to "prestaging",
          "freeFormDetails" to "canary-b"
        )
      }
      val pipeline = pipeline {
        stage {
          refId = "1"
          type = KayentaCanaryStage.STAGE_TYPE
          context["deployments"] = mapOf(
            "baseline" to baseline,
            "clusterPairs" to listOf(
              mapOf(
                "control" to controlClusterA,
                "experiment" to experimentClusterA
              ),
              mapOf(
                "control" to controlClusterB,
                "experiment" to experimentClusterB
              )
            )
          )
          stage {
            refId = "1<1"
            type = DeployCanaryClustersStage.STAGE_TYPE
          }
        }
      }
      val canaryDeployStage = pipeline.stageByRef("1<1")

      val beforeStages = subject.beforeStages(canaryDeployStage)

      it("creates a find image and deploy stages for the control cluster") {
        beforeStages.named("Find baseline image") {
          assertThat(type).isEqualTo(FindImageFromClusterStage.PIPELINE_CONFIG_TYPE)
          assertThat(requisiteStageRefIds).isEmpty()
          assertThat(context["application"]).isEqualTo(baseline["application"])
          assertThat(context["account"]).isEqualTo(baseline["account"])
          assertThat(context["cluster"]).isEqualTo(baseline["cluster"])
          assertThat(context["cloudProvider"]).isEqualTo("aws")
          assertThat(context["regions"]).isEqualTo(setOf("us-west-1"))
        }
        beforeStages.named("Deploy control clusters") {
          assertThat(type).isEqualTo(ParallelDeployStage.PIPELINE_CONFIG_TYPE)
          assertThat(requisiteStageRefIds).hasSize(1)
          assertThat(pipeline.stageByRef(requisiteStageRefIds.first()).name)
            .isEqualTo("Find baseline image")
          assertThat(context["clusters"]).isEqualTo(listOf(controlClusterA, controlClusterB))
        }
      }

      it("creates a deploy stage for the experiment cluster") {
        beforeStages.named("Deploy experiment clusters") {
          assertThat(type).isEqualTo(ParallelDeployStage.PIPELINE_CONFIG_TYPE)
          assertThat(requisiteStageRefIds).isEmpty()
          assertThat(context["clusters"]).isEqualTo(listOf(experimentClusterA, experimentClusterB))
        }
      }
    }
  }
})

fun List<Stage>.named(name: String, block: Stage.() -> Unit) {
  find { it.name == name }
    ?.apply(block)
    ?: fail("Expected a stage named '$name' but found ${map(Stage::getName)}")
}

fun StageDefinitionBuilder.beforeStages(stage: Stage) =
  StageGraphBuilder.beforeStages(stage).let { graph ->
    beforeStages(stage, graph)
    graph.build().toList().also {
      stage.execution.stages.addAll(it)
    }
  }
