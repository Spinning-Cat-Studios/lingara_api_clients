<?php

declare(strict_types=1);

namespace Lingara;

/** How the token exchange authenticates the client. The library never sends both. */
enum AuthMethod: string
{
    /** `client_secret_basic`: the Authorization header. The default. */
    case Basic = 'client_secret_basic';

    /** `client_secret_post`: client_id and client_secret in the form body. */
    case Post = 'client_secret_post';
}
