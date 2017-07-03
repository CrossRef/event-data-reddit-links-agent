# Crossref Event Data Reddit.com Links Agent

<img src="doc/logo.png" align="right" style="float: right">

Crossref Event Data Reddit Links agent. Subscribes to a list of Subreddits and follows the links shared on them to see if those webpages have Events. Note that this is different from the *Reddit* agent. 

Every 24 hours the Reddit agent ingests the `subreddits` Artifact and scans through each domain. Each domain, it scans through the API in order of newness, and continues until it's gathered 48 hours worth of data. This allows the agent to page through leisurely. This is packaged in upto an Evidence Package and sent to the Percolator. The Percolator takes care of excluding duplicate Actions.

## To run

To run as an agent, `lein run`. To update the rules in Gnip, which should be one when the domain list artifact is updated, `lein run update-rules`.

## Tests

### Unit tests

 - `time docker-compose -f docker-compose-unit-tests.yml run -w /usr/src/app test lein test :unit`



## Demo

    time docker-compose -f docker-compose-unit-tests.yml run -w /usr/src/app test lein repl

## Config

Uses Event Data global configuration namespace. Shares authentication details with Reddit Agent, but has its own JWT as it's considered a separate source.

 - `REDDIT_APP_NAME` e.g. crossref-bot
 - `REDDIT_PASSWORD` 
 - `REDDIT_CLIENT` as listed in the Reddit prefs, e.g. `KeTpPh7XGZwrdg`
 - `REDDIT_SECRET` as listed in Reddit prefs
 - `REDDIT_LINKS_JWT`
 - `GLOBAL_ARTIFACT_URL_BASE`, e.g. https://artifact.eventdata.crossref.org
 - `GLOBAL_KAFKA_BOOTSTRAP_SERVERS`
 - `GLOBAL_STATUS_TOPIC`
