# Baseline Cloud Monitoring alert policies for SeatFlow single-VM host
# Ops Agent is installed directly via bootstrap-seatflow-vm.sh on the VM.

resource "google_monitoring_alert_policy" "vm_high_cpu" {
  count        = var.enable_ops_agent ? 1 : 0
  project      = var.project_id
  display_name = "SeatFlow Production VM - High CPU Utilization"
  combiner     = "OR"

  conditions {
    display_name = "Compute Engine VM CPU utilization exceeds 90% for 5 minutes"

    condition_threshold {
      filter          = "metric.type=\"compute.googleapis.com/instance/cpu/utilization\" AND resource.type=\"gce_instance\""
      duration        = "300s"
      comparison      = "COMPARISON_GT"
      threshold_value = 0.90

      aggregations {
        alignment_period   = "60s"
        per_series_aligner = "ALIGN_MEAN"
      }
    }
  }

  alert_strategy {
    auto_close = "1800s"
  }

  depends_on = [google_project_service.services]
}

resource "google_monitoring_alert_policy" "vm_high_disk" {
  count        = var.enable_ops_agent ? 1 : 0
  project      = var.project_id
  display_name = "SeatFlow Production VM - High Disk Space Utilization"
  combiner     = "OR"

  conditions {
    display_name = "Guest Disk Space utilization exceeds 85%"

    condition_threshold {
      filter          = "metric.type=\"agent.googleapis.com/disk/percent_used\" AND resource.type=\"gce_instance\" AND metric.labels.state=\"used\""
      duration        = "300s"
      comparison      = "COMPARISON_GT"
      threshold_value = 85.0

      aggregations {
        alignment_period   = "60s"
        per_series_aligner = "ALIGN_MEAN"
      }
    }
  }

  alert_strategy {
    auto_close = "1800s"
  }

  depends_on = [google_project_service.services]
}
