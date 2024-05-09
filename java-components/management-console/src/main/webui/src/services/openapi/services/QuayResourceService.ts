/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { RepositoryPush } from '../models/RepositoryPush';
import type { CancelablePromise } from '../core/CancelablePromise';
import { OpenAPI } from '../core/OpenAPI';
import { request as __request } from '../core/request';
export class QuayResourceService {
    /**
     * @param requestBody
     * @returns any Created
     * @throws ApiError
     */
    public static postApiQuay(
        requestBody?: RepositoryPush,
    ): CancelablePromise<any> {
        return __request(OpenAPI, {
            method: 'POST',
            url: '/api/quay',
            body: requestBody,
            mediaType: 'application/json',
        });
    }
}
