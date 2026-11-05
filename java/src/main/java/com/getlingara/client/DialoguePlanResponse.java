package com.getlingara.client;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.getlingara.client.model.KeptReason;
import com.getlingara.client.model.PlanFragment;

/**
 * {@link LingaraClient#planDialogue}'s answer, tagged by {@code status}: {@code planned} with a
 * {@code fragment}, or {@code kept} with a {@code reason} (ADR 3.11.26n D4). A keep is a result,
 * never an error: keep the frontier you have. Hand-written because openapi-generator's oneOf class
 * needs support files this build does not generate.
 *
 * @param status {@code planned} or {@code kept}
 * @param fragment the planned beats, or null on a keep
 * @param reason why nothing was planned, or null on a plan
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DialoguePlanResponse(String status, PlanFragment fragment, KeptReason reason) {}
