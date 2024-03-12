/* generated using openapi-typescript-codegen -- do no edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { BuildDTO } from '../models/BuildDTO';
import type { PageParametersBuildListDTO } from '../models/PageParametersBuildListDTO';
import type { CancelablePromise } from '../core/CancelablePromise';
import { OpenAPI } from '../core/OpenAPI';
import { request as __request } from '../core/request';
export class BuildHistoryResourceService {
    /**
     * @param gav
     * @param label
     * @param labelValue
     * @param page
     * @param perPage
     * @param state
     * @param tool
     * @returns PageParametersBuildListDTO OK
     * @throws ApiError
     */
    public static getApiBuildsHistory(
        gav?: string,
        label?: string,
        labelValue?: string,
        page?: number,
        perPage?: number,
        state?: string,
        tool?: string,
    ): CancelablePromise<PageParametersBuildListDTO> {
        return __request(OpenAPI, {
            method: 'GET',
            url: '/api/builds/history',
            query: {
                'gav': gav,
                'label': label,
                'labelValue': labelValue,
                'page': page,
                'perPage': perPage,
                'state': state,
                'tool': tool,
            },
        });
    }
    /**
     * @param name
     * @returns any OK
     * @throws ApiError
     */
    public static getApiBuildsHistoryDiscoveryLogs(
        name: string,
    ): CancelablePromise<any> {
        return __request(OpenAPI, {
            method: 'GET',
            url: '/api/builds/history/discovery-logs/{name}',
            path: {
                'name': name,
            },
        });
    }
    /**
     * @param name
     * @returns BuildDTO OK
     * @throws ApiError
     */
    public static getBuild(
        name: string,
    ): CancelablePromise<BuildDTO> {
        return __request(OpenAPI, {
            method: 'GET',
            url: '/api/builds/history/{name}',
            path: {
                'name': name,
            },
        });
    }
}
