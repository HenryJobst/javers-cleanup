package io.github.henryjobst.javerscleanup;

import java.time.LocalDateTime;

record SnapshotRow(
        long id,
        long version,
        String type,
        String changedProperties,
        long commitFk,
        LocalDateTime commitDate,
        String typeName,      // null for Value Objects
        String localId,       // null for Value Objects
        String fragment,      // null for entities; VO path like "period" for Value Objects
        String ownerTypeName, // null for entities; owner entity type for Value Objects
        String ownerLocalId   // null for entities; owner entity localId for Value Objects
) {
    boolean isValueObject() {
        return fragment != null;
    }
}
