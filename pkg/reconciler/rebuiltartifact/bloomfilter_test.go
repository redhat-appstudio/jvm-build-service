package rebuiltartifact

import (
	"encoding/base64"
	"encoding/json"
	. "github.com/onsi/gomega"
	"os"
	"testing"
)

// because the creator is go and the user is Java we have a unique way of testing this
// we generate random explar data that we commit to the repo. The golang tests check that the
// generate function still results in the same output
// the java side checks that all of the items used to generate the data are flagged as being potentially in the set
func TestBloomFilter(t *testing.T) {
	g := NewGomegaWithT(t)
	data := []bloomFilterData{}
	file, err := os.ReadFile("../../../hack/bloom-filter-test-data.json")
	g.Expect(err).Should(BeNil())
	err = json.Unmarshal(file, &data)
	g.Expect(err).Should(BeNil())
	for _, filterStruct := range data {
		exemplarFilter, err := base64.StdEncoding.DecodeString(filterStruct.Filter)
		g.Expect(err).Should(BeNil())
		g.Expect(CreateBloomFilter(filterStruct.Items)).Should(Equal(exemplarFilter))
	}
}

type bloomFilterData struct {
	Filter string   `json:"filter,omitempty"`
	Items  []string `json:"items,omitempty"`
}
