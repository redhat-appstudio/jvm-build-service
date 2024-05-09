/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { CancelablePromise } from '../core/CancelablePromise';
import { OpenAPI } from '../core/OpenAPI';
import { request as __request } from '../core/request';
export class BuildAttemptResourceService {
    /**
     * @param name
     * @returns any OK
     * @throws ApiError
     */
    public static getApiBuildsAttemptsLogs(
        name: string,
    ): CancelablePromise<any> {
        return __request(OpenAPI, {
            method: 'GET',
            url: '/api/builds/attempts/logs/{name}',
            path: {
                'name': name,
            },
        });
    }
}
