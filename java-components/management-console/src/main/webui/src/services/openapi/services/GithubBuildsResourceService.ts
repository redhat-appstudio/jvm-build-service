/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { GithubBuildDTO } from '../models/GithubBuildDTO';
import type { PageParametersGithubBuildDTO } from '../models/PageParametersGithubBuildDTO';
import type { CancelablePromise } from '../core/CancelablePromise';
import { OpenAPI } from '../core/OpenAPI';
import { request as __request } from '../core/request';
export class GithubBuildsResourceService {
    /**
     * @param page
     * @param perPage
     * @returns PageParametersGithubBuildDTO OK
     * @throws ApiError
     */
    public static getApiBuildsGithub(
        page?: number,
        perPage?: number,
    ): CancelablePromise<PageParametersGithubBuildDTO> {
        return __request(OpenAPI, {
            method: 'GET',
            url: '/api/builds/github',
            query: {
                'page': page,
                'perPage': perPage,
            },
        });
    }
    /**
     * @param id
     * @returns GithubBuildDTO OK
     * @throws ApiError
     */
    public static getApiBuildsGithubId(
        id: number,
    ): CancelablePromise<GithubBuildDTO> {
        return __request(OpenAPI, {
            method: 'GET',
            url: '/api/builds/github/id/{id}',
            path: {
                'id': id,
            },
        });
    }
}
