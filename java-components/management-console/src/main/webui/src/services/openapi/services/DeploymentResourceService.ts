/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { DeploymentDTO } from '../models/DeploymentDTO';
import type { CancelablePromise } from '../core/CancelablePromise';
import { OpenAPI } from '../core/OpenAPI';
import { request as __request } from '../core/request';
export class DeploymentResourceService {
    /**
     * @returns DeploymentDTO OK
     * @throws ApiError
     */
    public static getApiDeployment(): CancelablePromise<Array<DeploymentDTO>> {
        return __request(OpenAPI, {
            method: 'GET',
            url: '/api/deployment',
        });
    }
}
