package io.github.redhatappstudio.jvmbuild.cli.util;

import io.github.redhatappstudio.jvmbuild.cli.model.Log;

/**
 * A log record is as follows:
 *
 * <pre>
 * {
 *     "result": {
 *         "name": "default/results/0dfc883d-722a-4489-9ab8-3cccc74ca4f6/logs/db6a5d59-2170-3367-9eb5-83f3d264ec62",
 *         "data": "base64encodedchunkpart1"
 *     }
 * }
 * </pre>
 */
public class Result {
    public Log result;
}
