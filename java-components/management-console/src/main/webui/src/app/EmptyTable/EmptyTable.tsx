import * as React from 'react';
import {
  EmptyState,
  EmptyStateBody,
  EmptyStateHeader,
  EmptyStateIcon,
} from '@patternfly/react-core';
import SearchIcon from '@patternfly/react-icons/dist/esm/icons/search-icon';

const EmptyTable: React.FunctionComponent = () => {

  return (
    <EmptyState>
      <EmptyStateHeader headingLevel="h4" titleText="No results found" icon={<EmptyStateIcon icon={SearchIcon} />} />
      <EmptyStateBody>No results match the criteria.</EmptyStateBody>
    </EmptyState>
  );
};
export {EmptyTable};
