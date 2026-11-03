# Project brain

<!-- mxcli-brain -->

Project knowledge that mxcli cannot compute: why a pattern was chosen here,
which marketplace version broke what, what a recurring mxbuild error means in
this app.

**Anything mxcli can answer does not belong here.** Entities, microflows, pages,
bindings and references are all queryable — a note that transcribes them is a
note that will disagree with the project.

## Layout

    project.md           cross-cutting decisions; loaded every session
    modules/<Module>.md  decisions anchored to one module; loaded when it is in play

An entry's anchors decide its file: `@Sales.Order` puts it in
`modules/Sales.md`. An entry with no anchor is cross-cutting and lives in
`project.md`.

## Working with it

    mxcli brain capture "<text>" --anchor @Module.Element   queue something
    mxcli brain staged                                      review the queue
    mxcli brain promote <id>                                write it into a shard
    mxcli brain check                                       anchors still resolve?
    mxcli brain show                                        size and headroom

Promotion is a human step on purpose: an agent queues, a person decides what is
worth committing.

Sizes and headroom are computed by `mxcli brain show` and are deliberately
not written down anywhere, including in this file — a figure in prose is stale
the next time anyone promotes.
