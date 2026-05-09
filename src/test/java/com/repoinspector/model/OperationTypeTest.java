package com.repoinspector.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OperationTypeTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "findById,      READ",
            "findAll,       READ",
            "getUser,       READ",
            "getAll,        READ",
            "countBy,       READ",
            "existsById,    READ",
            "loadUser,      READ",
            "fetchData,     READ",
            "readRecord,    READ",
            "queryAll,      READ",
            "listUsers,     READ",
            "searchByName,  READ",
    })
    void readPrefixes(String methodName, OperationType expected) {
        assertEquals(expected, OperationType.fromMethodName(methodName));
    }

    @ParameterizedTest(name = "{0} -> WRITE")
    @CsvSource({
            "saveUser",
            "deleteById",
            "removeRecord",
            "updateStatus",
            "createUser",
            "insertRecord",
            "addItem",
            "putData",
            "mergeEntity",
            "archiveUser",
            "pruneExpired",
            "markDeleted",
            "appendLog",
            "flushCache",
            "persistEntity",
            "storeResult",
            "setFlag",
    })
    void writePrefixes(String methodName) {
        assertEquals(OperationType.WRITE, OperationType.fromMethodName(methodName));
    }

    @Test
    void null_returnsUnknown() {
        assertEquals(OperationType.UNKNOWN, OperationType.fromMethodName(null));
    }

    @Test
    void emptyString_returnsUnknown() {
        assertEquals(OperationType.UNKNOWN, OperationType.fromMethodName(""));
    }

    @Test
    void noKnownPrefix_defaultsToRead() {
        assertEquals(OperationType.READ, OperationType.fromMethodName("customAction"));
        assertEquals(OperationType.READ, OperationType.fromMethodName("performOp"));
    }

    @Test
    void caseInsensitive_write() {
        assertEquals(OperationType.WRITE, OperationType.fromMethodName("SAVEIT"));
        assertEquals(OperationType.WRITE, OperationType.fromMethodName("DeleteById"));
    }
}
