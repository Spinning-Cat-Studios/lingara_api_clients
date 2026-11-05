package com.getlingara.kotlin.embed

import com.getlingara.kotlin.model.KeptReason
import com.getlingara.kotlin.model.PlanFragment
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * [planDialogue]'s answer, tagged by [status]: `planned` with [fragment], or `kept` with [reason]
 * (ADR 3.11.26n D4). A keep is a result, never an error: keep the frontier you have. Hand-written
 * because openapi-generator merges the two arms into one class that decodes neither.
 */
@Serializable
public data class DialoguePlanResponse(
    @SerialName("status") val status: String,
    @SerialName("fragment") val fragment: PlanFragment? = null,
    @SerialName("reason") val reason: KeptReason? = null,
)
