package tracker

func (mt *MusicTracker) GetTrackedFromDate() string {
	mt.mu.RLock()
	defer mt.mu.RUnlock()
	var firstDate string
	// Fetch the earliest date from daily history
	err := mt.db.Conn.QueryRow("SELECT MIN(date) FROM daily_stats").Scan(&firstDate)
	if err != nil || firstDate == "" {
		firstDate = "Unknown"
	}
	return firstDate
}
