<?php

declare(strict_types=1);

namespace LingaraSnippets;

use Lingara\Client;

function getUsage(Client $client): void
{
    // lingara:begin getUsage
    foreach ($client->getUsage()->value->getAllowance() as $row) {
        echo $row->getFeature(), ': ', $row->getRemaining(), ' of ', $row->getLimit(), ' left this ', $row->getWindow(), "\n";
    }
    // lingara:end
}
