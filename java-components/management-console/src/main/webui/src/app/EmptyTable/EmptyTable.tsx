import * as React from 'react';
import {
  EmptyState,
  EmptyStateBody,
  EmptyStateHeader,
  EmptyStateIcon,
} from '@patternfly/react-core';
import SearchIcon from '@patternfly/react-icons/dist/esm/icons/search-icon';
import {Td, Tr} from "@patternfly/react-table";

const EmptyTable: React.FunctionComponent = () => {

  return (
    <Tr>
      <Td>
        <EmptyState>
          <EmptyStateHeader headingLevel="h4" titleText="No results found" icon={<EmptyStateIcon icon={SearchIcon} />} />
            <EmptyStateBody>No results match the criteria.</EmptyStateBody>
        </EmptyState>
      </Td>
    </Tr>
  );
};
export {EmptyTable};
