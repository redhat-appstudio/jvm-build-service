/* generated using openapi-typescript-codegen -- do no edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { PageParametersBuildQueueListDTO } from '../models/PageParametersBuildQueueListDTO';
import type { CancelablePromise } from '../core/CancelablePromise';
import { OpenAPI } from '../core/OpenAPI';
import { request as __request } from '../core/request';
export class BuildQueueResourceService {
    /**
     * @param page
     * @param perPage
     * @returns PageParametersBuildQueueListDTO OK
     * @throws ApiError
     */
    public static getApiBuildsQueue(
        page?: number,
        perPage?: number,
    ): CancelablePromise<PageParametersBuildQueueListDTO> {
        return __request(OpenAPI, {
            method: 'GET',
            url: '/api/builds/queue',
            query: {
                'page': page,
                'perPage': perPage,
            },
        });
    }
    /**
     * @param requestBody
     * @returns any Created
     * @throws ApiError
     */
    public static postApiBuildsQueue(
        requestBody?: string,
    ): CancelablePromise<any> {
        return __request(OpenAPI, {
            method: 'POST',
            url: '/api/builds/queue',
            body: requestBody,
            mediaType: 'text/plain',
        });
    }
    /**
     * @param requestBody
     * @returns any Created
     * @throws ApiError
     */
    public static postApiBuildsQueueAdd(
        requestBody?: string,
    ): CancelablePromise<any> {
        return __request(OpenAPI, {
            method: 'POST',
            url: '/api/builds/queue/add',
            body: requestBody,
            mediaType: 'text/plain',
        });
    }
}
