<?php

declare(strict_types=1);

namespace Lingara\Exception;

/**
 * The one error family (K3): every failure a call throws is one of
 * ApiException, OAuthException, MaintenanceException or TransportException,
 * so `catch (LingaraException $e)` handles all four. Cancellation is not a
 * variant: leaving a `foreach` throws nothing.
 */
interface LingaraException extends \Throwable {}
