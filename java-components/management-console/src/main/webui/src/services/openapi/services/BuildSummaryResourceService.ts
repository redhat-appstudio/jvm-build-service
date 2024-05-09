/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { BuildSummaryDTO } from '../models/BuildSummaryDTO';
import type { CancelablePromise } from '../core/CancelablePromise';
import { OpenAPI } from '../core/OpenAPI';
import { request as __request } from '../core/request';
export class BuildSummaryResourceService {
    /**
     * @param label
     * @returns BuildSummaryDTO OK
     * @throws ApiError
     */
    public static getApiBuildsStatus(
        label?: string,
    ): CancelablePromise<BuildSummaryDTO> {
        return __request(OpenAPI, {
            method: 'GET',
            url: '/api/builds/status',
            query: {
                'label': label,
            },
        });
    }
}
