/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { ReplayEvent } from '../models/ReplayEvent';
import type { CancelablePromise } from '../core/CancelablePromise';
import { OpenAPI } from '../core/OpenAPI';
import { request as __request } from '../core/request';
export class DefaultService {
    /**
     * @returns ReplayEvent OK
     * @throws ApiError
     */
    public static getApiReplayEvents(): CancelablePromise<Array<ReplayEvent>> {
        return __request(OpenAPI, {
            method: 'GET',
            url: '/api/replay/events',
        });
    }
    /**
     * @returns ReplayEvent OK
     * @throws ApiError
     */
    public static putApiReplayEvents(): CancelablePromise<Array<ReplayEvent>> {
        return __request(OpenAPI, {
            method: 'PUT',
            url: '/api/replay/events',
        });
    }
    /**
     * @returns ReplayEvent OK
     * @throws ApiError
     */
    public static postApiReplayEvents(): CancelablePromise<Array<ReplayEvent>> {
        return __request(OpenAPI, {
            method: 'POST',
            url: '/api/replay/events',
        });
    }
    /**
     * @returns ReplayEvent OK
     * @throws ApiError
     */
    public static deleteApiReplayEvents(): CancelablePromise<Array<ReplayEvent>> {
        return __request(OpenAPI, {
            method: 'DELETE',
            url: '/api/replay/events',
        });
    }
    /**
     * @returns ReplayEvent OK
     * @throws ApiError
     */
    public static optionsApiReplayEvents(): CancelablePromise<Array<ReplayEvent>> {
        return __request(OpenAPI, {
            method: 'OPTIONS',
            url: '/api/replay/events',
        });
    }
    /**
     * @returns ReplayEvent OK
     * @throws ApiError
     */
    public static headApiReplayEvents(): CancelablePromise<Array<ReplayEvent>> {
        return __request(OpenAPI, {
            method: 'HEAD',
            url: '/api/replay/events',
        });
    }
    /**
     * @returns ReplayEvent OK
     * @throws ApiError
     */
    public static patchApiReplayEvents(): CancelablePromise<Array<ReplayEvent>> {
        return __request(OpenAPI, {
            method: 'PATCH',
            url: '/api/replay/events',
        });
    }
}
