package rebuiltartifact

const max = 1024 * 1000

func CreateBloomFilter(items []string) []byte {
	size := len(items) * 2 //16 bits per item, should give very low error rates
	if size > max {
		size = max
	} else if size < 100 {
		size = 100
	}
	filter := make([]byte, size)
	for _, item := range items {
		for i := int32(1); i <= 10; i++ {
			hash := doHash(i, item)
			var totalBits = int32(size * 8)
			hash = hash % totalBits
			if hash < 0 {
				hash = hash * -1
			}
			var pos = hash / 8
			var bit = hash % 8
			filter[pos] = filter[pos] | (1 << bit)
		}
	}
	return filter
}
