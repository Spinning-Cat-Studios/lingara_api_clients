<?php

declare(strict_types=1);

namespace Lingara\Exception;

/** Why a call had no usable HTTP answer (K3). The value is the contract's name. */
enum TransportKind: string
{
    case Connect = 'connect';
    case Tls = 'tls';
    case Reset = 'reset';
    case Timeout = 'timeout';
    case StreamEndedEarly = 'stream_ended_early';
    case MalformedResponse = 'malformed_response';
    case MalformedEvent = 'malformed_event';
}
