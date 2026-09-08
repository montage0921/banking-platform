/**
 * Savings Goal Card Component
 *
 * Displays a single savings goal with:
 * - Progress bar
 * - Progress percentage
 * - Current balance vs target
 * - Days remaining to deadline
 * - Status badge (NOT_STARTED, IN_PROGRESS, ACHIEVED, OVERDUE)
 * - Edit and Delete buttons
 *
 * Supports both Classic and Neon themes
 */

import React, { useState } from "react";
import PropTypes from "prop-types";
import GoalProgressBar from "./GoalProgressBar";

const SavingsGoalCard = ({
  goal = {},
  onEdit = () => {},
  onDelete = () => {},
  isLoading = false,
  theme = "classic",
}) => {
  const [showDeleteConfirm, setShowDeleteConfirm] = useState(false);

  if (!goal || !goal.goal_id) {
    return null;
  }

  const getStatusColor = (status) => {
    const colors = {
      NOT_STARTED: "status-not-started",
      IN_PROGRESS: "status-in-progress",
      ACHIEVED: "status-achieved",
      OVERDUE: "status-overdue",
    };
    return colors[status] || "status-unknown";
  };

  const getStatusLabel = (status) => {
    const labels = {
      NOT_STARTED: "Not Started",
      IN_PROGRESS: "In Progress",
      ACHIEVED: "Achieved",
      OVERDUE: "Overdue",
    };
    return labels[status] || status;
  };

  const getThemeClass = () => {
    return theme === "neon" ? "goal-card-neon" : "goal-card-classic";
  };

  const getRecommendedContribution = () => {
    const remainingAmount = Math.max(
      0,
      (goal.target_amount || 0) - (goal.current_balance || 0),
    );

    if (remainingAmount === 0 || goal.time_remaining_days <= 0) {
      return 0;
    }

    const monthsRemaining = Math.max(1, goal.time_remaining_days / 30.44);
    return remainingAmount / monthsRemaining;
  };

  const recommendedContribution = getRecommendedContribution();
  return (
    <div className={`goal-card ${getThemeClass()}`}>
      <div className="card-header">
        <div className="header-left">
          <h3 className="goal-name">{goal.goal_name}</h3>
          <p className="account-number">
            Account: {goal.account_number} ({goal.account_type})
          </p>
        </div>
        <div className={`status-badge ${getStatusColor(goal.status)}`}>
          {getStatusLabel(goal.status)}
        </div>
      </div>

      <div className="card-body">
        <GoalProgressBar
          progressPercentage={goal.progress_percentage}
          currentBalance={goal.current_balance}
          targetAmount={goal.target_amount}
          theme={theme}
        />

        <div className="goal-details">
          <div className="detail-row">
            <span className="detail-label">Time Remaining:</span>
            <span className="detail-value">
              {goal.time_remaining_days > 0
                ? `${goal.time_remaining_days} days`
                : "Past deadline"}
            </span>
          </div>

          {/* Add this new block for the Recommended Contribution */}
          <div className="detail-row">
            <span className="detail-label">Recommended (Monthly):</span>
            <span className="detail-value">
              ${recommendedContribution.toFixed(2)} / mo
            </span>
          </div>
          <div className="detail-row">
            <span className="detail-label">Target:</span>
            <span className="detail-value">
              ${goal.target_amount.toFixed(2)}
            </span>
          </div>
          <div className="detail-row">
            <span className="detail-label">Deadline:</span>
            <span className="detail-value">
              {goal.target_date
                ? new Date(goal.target_date + "T00:00:00").toLocaleDateString()
                : ""}
            </span>
          </div>
        </div>
      </div>

      <div className="card-footer">
        <button
          className="btn btn-outline-secondary"
          onClick={() => onEdit(goal)}
          disabled={isLoading}
        >
          Edit
        </button>
        <button
          className="btn btn-outline-danger"
          onClick={() => setShowDeleteConfirm(true)}
          disabled={isLoading}
        >
          Delete
        </button>
      </div>

      {showDeleteConfirm && (
        <div className="delete-confirmation-inline">
          <p>Are you sure you want to delete this goal?</p>
          <div className="confirmation-buttons">
            <button
              className="btn btn-secondary"
              onClick={() => setShowDeleteConfirm(false)}
              disabled={isLoading}
            >
              Cancel
            </button>
            <button
              className="btn btn-danger"
              onClick={() => {
                onDelete(goal);
                setShowDeleteConfirm(false);
              }}
              disabled={isLoading}
            >
              Delete
            </button>
          </div>
        </div>
      )}
    </div>
  );
};

SavingsGoalCard.propTypes = {
  goal: PropTypes.shape({
    goal_id: PropTypes.number,
    goal_name: PropTypes.string,
    account_number: PropTypes.string,
    account_type: PropTypes.string,
    target_amount: PropTypes.number,
    target_date: PropTypes.string,
    current_balance: PropTypes.number,
    progress_percentage: PropTypes.number,
    time_remaining_days: PropTypes.number,
    status: PropTypes.oneOf([
      "NOT_STARTED",
      "IN_PROGRESS",
      "ACHIEVED",
      "OVERDUE",
    ]),
  }),
  onEdit: PropTypes.func,
  onDelete: PropTypes.func,
  isLoading: PropTypes.bool,
  theme: PropTypes.oneOf(["classic", "neon"]),
};

export default SavingsGoalCard;
