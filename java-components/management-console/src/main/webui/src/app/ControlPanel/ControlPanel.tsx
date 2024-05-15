import * as React from 'react';

import {
  ActionList, ActionListItem,
  Button,
  Card,
  CardBody,
  CardHeader,
} from "@patternfly/react-core";
import {AdminResourceService} from "../../services/openapi";


const ControlPanel: React.FunctionComponent = () => {

    return <Card>
      <CardHeader>
        Rebuild
      </CardHeader>
    <CardBody>
      <ActionList>
        <ActionListItem>
          <Button variant="danger" id="rebuild-all" onClick={AdminResourceService.postApiAdminRebuildAll}>
            Rebuild All
          </Button>
        </ActionListItem>
        <ActionListItem>
          <Button variant="warning" id="rebuild-failed" onClick={AdminResourceService.postApiAdminRebuildFailed}>
            Rebuild Failed
          </Button>
        </ActionListItem>
        <ActionListItem>
          <Button variant="danger" id="rebuild-failed" onClick={AdminResourceService.postApiAdminCleanOutDatabase}>
            Delete All Data
          </Button>
        </ActionListItem>
        <ActionListItem>
          <Button variant="warning" id="s3-import" onClick={AdminResourceService.postApiAdminImportFroms3}>
            S3 Import
          </Button>
        </ActionListItem>
        <ActionListItem>
          <Button variant="warning" id="s3-import" onClick={AdminResourceService.postApiAdminClearBuildQueue}>
            Clear Build Queue
          </Button>
        </ActionListItem>
      </ActionList>
    </CardBody>
  </Card>

}

export {ControlPanel};
