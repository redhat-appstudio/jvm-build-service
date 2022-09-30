package main

import (
	"encoding/base64"
	json2 "encoding/json"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/rebuiltartifact"
	"math/rand"
	"os"
)

var letters = []rune("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ:-")

func randSeq(n int) string {
	b := make([]rune, n)
	for i := range b {
		b[i] = letters[rand.Intn(len(letters))]
	}
	return string(b)
}

// Generates the test data used by both the go and Java tests
func main() {
	sizes := []int{10, 100, 1000, 10000}
	var results []bloomFilterData
	for _, size := range sizes {
		var items []string
		for i := 0; i < size; i = i + 1 {
			items = append(items, randSeq(5+rand.Intn(40)))
		}
		results = append(results, bloomFilterData{Filter: base64.StdEncoding.EncodeToString(rebuiltartifact.CreateBloomFilter(items)), Items: items})
	}
	json, err := json2.Marshal(results)
	if err != nil {
		panic(err)
	}

	err = os.WriteFile("hack/bloom-filter-test-data.json", json, 0644)
	if err != nil {
		panic(err)
	}
}

type bloomFilterData struct {
	Filter string   `json:"filter,omitempty"`
	Items  []string `json:"items,omitempty"`
}
