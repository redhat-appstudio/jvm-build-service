/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { ArtifactDTO } from '../models/ArtifactDTO';
import type { PageParametersArtifactListDTO } from '../models/PageParametersArtifactListDTO';
import type { CancelablePromise } from '../core/CancelablePromise';
import { OpenAPI } from '../core/OpenAPI';
import { request as __request } from '../core/request';
export class ArtifactHistoryResourceService {
    /**
     * @param gav
     * @param page
     * @param perPage
     * @param state
     * @returns PageParametersArtifactListDTO OK
     * @throws ApiError
     */
    public static getApiArtifactsHistory(
        gav?: string,
        page?: number,
        perPage?: number,
        state?: string,
    ): CancelablePromise<PageParametersArtifactListDTO> {
        return __request(OpenAPI, {
            method: 'GET',
            url: '/api/artifacts/history',
            query: {
                'gav': gav,
                'page': page,
                'perPage': perPage,
                'state': state,
            },
        });
    }
    /**
     * @param name
     * @returns ArtifactDTO OK
     * @throws ApiError
     */
    public static getArtifact(
        name: string,
    ): CancelablePromise<ArtifactDTO> {
        return __request(OpenAPI, {
            method: 'GET',
            url: '/api/artifacts/history/{name}',
            path: {
                'name': name,
            },
        });
    }
}
