/* generated using openapi-typescript-codegen -- do no edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { DependencySetDTO } from '../models/DependencySetDTO';
import type { CancelablePromise } from '../core/CancelablePromise';
import { OpenAPI } from '../core/OpenAPI';
import { request as __request } from '../core/request';
export class DependencySetResourceService {
    /**
     * @param id
     * @returns DependencySetDTO OK
     * @throws ApiError
     */
    public static getApiDependencySet(
        id: number,
    ): CancelablePromise<DependencySetDTO> {
        return __request(OpenAPI, {
            method: 'GET',
            url: '/api/dependency-set/{id}',
            path: {
                'id': id,
            },
        });
    }
}
