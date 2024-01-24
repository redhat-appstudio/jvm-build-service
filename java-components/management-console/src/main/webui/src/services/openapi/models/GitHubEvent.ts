/* generated using openapi-typescript-codegen -- do no edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { JsonObject } from './JsonObject';
export type GitHubEvent = {
    installationId?: number;
    appName?: string | null;
    deliveryId?: string;
    repository?: string | null;
    event?: string;
    action?: string;
    payload?: string;
    parsedPayload?: JsonObject;
    replayed?: boolean;
    eventAction?: string;
};

