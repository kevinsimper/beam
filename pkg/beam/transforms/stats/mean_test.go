package stats

import (
	"testing"

	"github.com/apache/beam/sdks/go/pkg/beam/testing/ptest"
	"github.com/apache/beam/sdks/go/pkg/beam/testing/passert"
	"github.com/apache/beam/sdks/go/pkg/beam"
)

// TestMeanInt verifies that Mean works correctly for ints.
func TestMeanInt(t *testing.T) {
	tests := []struct {
		in  []int
		exp []float64
	}{
		{
			[]int{1, -4},
			[]float64{-1.5},
		},
		{
			[]int{7, 11, 7, 5, 10},
			[]float64{8},
		},
		{
			[]int{0, -2, -10},
			[]float64{-4},
		},
		{
			[]int{-9},
			[]float64{-9},
		},
	}

	for _, test := range tests {
		p, in, exp := ptest.CreateList2(test.in, test.exp)
		passert.Equals(p, Mean(p, in), exp)

		if err := ptest.Run(p); err != nil {
			t.Errorf("Mean(%v) != %v: %v", test.in, test.exp, err)
		}
	}
}

// TestMeanFloat verifies that Mean works correctly for float64s.
func TestMeanFloat(t *testing.T) {
	tests := []struct {
		in  []float64
		exp []float64
	}{
		{
			[]float64{1, -2, 3.5, 1},
			[]float64{0.875},
		},
		{
			[]float64{0, -99.99, 1, 1},
			[]float64{-24.4975},
		},
		{
			[]float64{5.67890},
			[]float64{5.6789},
		},
	}

	for _, test := range tests {
		p, in, exp := ptest.CreateList2(test.in, test.exp)
		passert.Equals(p, Mean(p, in), exp)

		if err := ptest.Run(p); err != nil {
			t.Errorf("Mean(%v) != %v: %v", test.in, test.exp, err)
		}
	}
}

// TestMeanKeyed verifies that Mean works correctly for KV values.
func TestMeanKeyed(t *testing.T) {
	tests := []struct {
		in  []student
		exp []student
	}{
		{
			[]student{{"alpha", 1}, {"beta", 4}, {"charlie",3.5}},
			[]student{{"alpha", 1}, {"beta", 4}, {"charlie",3.5}},
		},
		{
			[]student{{"alpha", 1}},
			[]student{{"alpha",1}},
		},
		{
			[]student{{"alpha", 1}, {"alpha", -4},{"beta", 4}, {"charlie",0},{"charlie",5.5}},
			[]student{{"alpha", -1.5},{"beta", 4},{"charlie",2.75}},
		},
	}

	for _, test := range tests {
		p, in, exp := ptest.CreateList2(test.in, test.exp)
		kv := beam.ParDo(p, studentToKV, in)
		mean := Mean(p, kv)
		meanStudent := beam.ParDo(p, kvToStudent, mean)
		passert.Equals(p, meanStudent, exp)

		if err := ptest.Run(p); err != nil {
			t.Errorf("Mean(%v) != %v: %v", test.in, test.exp, err)
		}
	}
}
