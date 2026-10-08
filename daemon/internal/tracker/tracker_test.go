package tracker

import (
	"os"
	"path/filepath"
	"reflect"
	"testing"
)

func TestSplitArtists(t *testing.T) {
	tests := []struct {
		input    string
		expected []string
	}{
		{"Palak Muchhal & Arijit Singh", []string{"Palak Muchhal", "Arijit Singh"}},
		{"Tulsi Kumar & KK", []string{"Tulsi Kumar", "KK"}},
		{"Drake feat. Rihanna", []string{"Drake", "Rihanna"}},
		{"Alan Walker, Sabrina Carpenter & Farruko", []string{"Alan Walker", "Sabrina Carpenter", "Farruko"}},
		{"Mustafa Zahid", []string{"Mustafa Zahid"}},
		{"Arko & Puneet Sharma", []string{"Arko", "Puneet Sharma"}},
	}

	for _, tt := range tests {
		res := SplitArtists(tt.input)
		if !reflect.DeepEqual(res, tt.expected) {
			t.Errorf("SplitArtists(%q) = %v; want %v", tt.input, res, tt.expected)
		}
	}
}

func TestSQLiteTracker(t *testing.T) {
	tempDir, err := os.MkdirTemp("", "quazaar_test_*")
	if err != nil {
		t.Fatalf("Failed to create temp dir: %v", err)
	}
	defer os.RemoveAll(tempDir)

	tr, err := NewMusicTracker(tempDir)
	if err != nil {
		t.Fatalf("Failed to initialize tracker: %v", err)
	}
	defer tr.Close()

	// 1. YouTube should NOT be recorded
	if tr.RecordPlay("com.google.android.youtube", "Some Video", []string{"Youtuber"}, "", "phone", "Phone", 0) {
		t.Errorf("Expected YouTube play to be ignored")
	}

	// 2. Apple Music play should be recorded
	artists := SplitArtists("Palak Muchhal & Arijit Singh")
	recorded := tr.RecordPlay("com.apple.android.music", "Meri Aashiqui", artists, "Aashiqui 2", "mobile-tb132fu", "TB132FU", 260000)
	if !recorded {
		t.Fatalf("Expected Apple Music play to be recorded")
	}

	data := tr.GetData()
	if data.TotalPlays != 1 {
		t.Errorf("Expected 1 total play, got %d", data.TotalPlays)
	}

	if len(data.Tracks) != 1 {
		t.Fatalf("Expected 1 track, got %d", len(data.Tracks))
	}

	for _, trk := range data.Tracks {
		if trk.Title != "Meri Aashiqui" {
			t.Errorf("Track title mismatch: %s", trk.Title)
		}
		if !reflect.DeepEqual(trk.Artists, []string{"Palak Muchhal", "Arijit Singh"}) {
			t.Errorf("Track artists mismatch: %v", trk.Artists)
		}
		if trk.PlayCount != 1 {
			t.Errorf("Track play count expected 1, got %d", trk.PlayCount)
		}
	}

	// Check that SQLite file exists
	dbFile := filepath.Join(tempDir, "music_history.db")
	if _, err := os.Stat(dbFile); err != nil {
		t.Errorf("SQLite database file not created: %v", err)
	}
}
