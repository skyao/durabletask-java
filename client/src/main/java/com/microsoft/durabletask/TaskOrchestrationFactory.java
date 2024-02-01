// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.
package com.microsoft.durabletask;

/**
 * Factory interface for producing {@link TaskOrchestration} implementations.
 */
public interface TaskOrchestrationFactory {

    public static final int DEFAULT_REVISION = 1;

    /**
     * Gets the name of the orchestration this factory creates.
     * @return the name of the orchestration
     */
    String getName();

    /**
     * Gets the revision of the orchestration this factory creates.
     * 
     * @return the revision of the orchestration
     */
    default int getRevision() {
        return DEFAULT_REVISION;
    }

    /**
     * Creates a new instance of {@link TaskOrchestration}
     * @return the created orchestration instance
     */
    TaskOrchestration create();
}
