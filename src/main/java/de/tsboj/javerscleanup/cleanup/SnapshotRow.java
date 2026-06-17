package de.tsboj.javerscleanup.cleanup;

import java.time.LocalDateTime;

record SnapshotRow(
        long id,
        long version,
        String type,
        String changedProperties,
        long commitFk,
        LocalDateTime commitDate,
        String typeName,
        String localId
) {}
