package com.teamflow.framework.audit;

public interface OperationLogRecorder {

    void record(OperationLogEntry entry);
}
