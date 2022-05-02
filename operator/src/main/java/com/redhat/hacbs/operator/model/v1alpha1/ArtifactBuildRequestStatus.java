package com.redhat.hacbs.operator.model.v1alpha1;

public class ArtifactBuildRequestStatus {
    public enum State {
        /**
         * A new resource that has not been acted on by the operator
         */
        NEW,
        /**
         * The discovery pipeline is running to try and figure out how to build this artifact
         */
        DISCOVERING,
        /**
         * The discovery pipeline failed to find a way to build this
         */
        MISSING,
        /**
         * The build is running
         */
        BUILDING,
        /**
         * The build failed
         */
        FAILED,
        /**
         * The build completed successfully, the resource can be removed
         */
        COMPLETE

    }

    private State state = State.NEW;
    private String message;

    public State getState() {
        return state;
    }

    public ArtifactBuildRequestStatus setState(State state) {
        this.state = state;
        return this;
    }

    public String getMessage() {
        return message;
    }

    public ArtifactBuildRequestStatus setMessage(String message) {
        this.message = message;
        return this;
    }
}
