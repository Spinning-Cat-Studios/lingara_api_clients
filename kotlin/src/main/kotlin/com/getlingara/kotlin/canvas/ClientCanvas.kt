package com.getlingara.kotlin.canvas

import com.getlingara.kotlin.LingaraClient

// The canvas group (ADR 16.10.26ak) is an extension rather than a member because `LingaraClient.kt`
// is at its function budget, as the embedding operations are; import it from
// `com.getlingara.kotlin.canvas`.

/**
 * The canvas operations (scopes `canvas:read`, `canvas:write`), over this client's request path:
 * its token, retries, version pin and errors.
 */
public val LingaraClient.canvases: Canvases
    get() = Canvases(requests)
