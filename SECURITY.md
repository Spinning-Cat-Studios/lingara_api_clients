# Security

## Reporting a vulnerability

Email **support@getlingara.com** with "Security" in the subject line, and
describe the issue and the steps to reproduce it. Please do not open a public
issue for a vulnerability.

## Credentials

Every library authenticates with an OAuth 2.0 client id and secret, exchanged
for a one-hour access token. The libraries never log a client secret or an
access token, at any log level; a library that does is a vulnerability, and we
want to hear about it.

Keep client secrets out of source control. Create and revoke clients on the
Integrations page of your Lingara account.

## Release signing

The Maven Central artefacts `com.getlingara:lingara-java` and
`com.getlingara:lingara-kotlin` are signed with the Lingara release key,
`Lingara Release Signing <support@getlingara.com>`, whose fingerprint is:

    084F59E770709FC8C082A8F0C5EE6D0DB337D775

The public key is on `keyserver.ubuntu.com` and `keys.openpgp.org`. A
signature by any other key did not come from us.
