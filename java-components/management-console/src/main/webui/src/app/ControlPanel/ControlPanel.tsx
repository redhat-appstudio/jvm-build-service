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
          <Button variant="danger" id="rebuild-all" onClick={() => AdminResourceService.postApiAdminRebuildAll()}>
            Rebuild All
          </Button>
        </ActionListItem>
        <ActionListItem>
          <Button variant="warning" id="rebuild-failed" onClick={() => AdminResourceService.postApiAdminRebuildFailed()}>
            Rebuild Failed
          </Button>
        </ActionListItem>
      </ActionList>
    </CardBody>
  </Card>

}

export {ControlPanel};
