/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { RunningBuildDTO } from '../models/RunningBuildDTO';
import type { CancelablePromise } from '../core/CancelablePromise';
import { OpenAPI } from '../core/OpenAPI';
import { request as __request } from '../core/request';
export class RunningBuildsResourceService {
    /**
     * @returns RunningBuildDTO OK
     * @throws ApiError
     */
    public static getApiBuildsRunning(): CancelablePromise<Array<RunningBuildDTO>> {
        return __request(OpenAPI, {
            method: 'GET',
            url: '/api/builds/running',
        });
    }
}
