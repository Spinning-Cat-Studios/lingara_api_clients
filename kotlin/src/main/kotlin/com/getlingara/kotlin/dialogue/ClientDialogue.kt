package com.getlingara.kotlin.dialogue

import com.getlingara.kotlin.LingaraClient

// The dialogue group (ADR 29.10.26c) is an extension rather than a member because `LingaraClient.kt`
// is at its function budget, as the canvas group is; import it from
// `com.getlingara.kotlin.dialogue`.

/**
 * The stored-dialogue operations (scopes `dialogues:read`, `dialogues:write`), over this client's
 * request path: its token, retries, version pin and errors.
 */
public val LingaraClient.dialogues: Dialogues
    get() = Dialogues(requests)
