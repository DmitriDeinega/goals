import { useState, useEffect } from 'react'
import {
  DndContext,
  closestCenter,
  PointerSensor,
  TouchSensor,
  useSensor,
  useSensors,
} from '@dnd-kit/core'
import { restrictToVerticalAxis, restrictToParentElement } from '@dnd-kit/modifiers'
import {
  SortableContext,
  verticalListSortingStrategy,
  useSortable,
  arrayMove,
} from '@dnd-kit/sortable'
import { CSS } from '@dnd-kit/utilities'
import GoalForm from '../components/GoalForm'

const TYPE_LABEL = {
  daily: (g) => g.times_per_day > 1 ? `${g.times_per_day}× / day` : 'Daily',
  weekly_x: (g) => `${g.times_per_week}× / week`,
}

function ConfirmDialog({ message, onConfirm, onCancel }) {
  useEffect(() => {
    const onKey = (e) => { if (e.key === 'Escape') onCancel() }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [onCancel])

  return (
    <div className="overlay" onMouseDown={e => e.target === e.currentTarget && onCancel()}>
      <div className="sheet confirm-dialog">
        <div className="confirm-message">{message}</div>
        <div className="sheet-actions">
          <button className="btn-secondary" onClick={onCancel}>Cancel</button>
          <button className="btn-danger" onClick={onConfirm}>Delete</button>
        </div>
      </div>
    </div>
  )
}

function SortableGoalCard({ goal, onEdit }) {
  const { attributes, listeners, setNodeRef, setActivatorNodeRef, transform, transition, isDragging } = useSortable({ id: goal.id })

  const style = {
    transform: CSS.Transform.toString(transform),
    transition,
    opacity: isDragging ? 0.4 : 1,
  }

  return (
    <div
      ref={setNodeRef}
      style={style}
      className={`goal-card ${!goal.enabled ? 'disabled' : ''}`}
    >
      <button
        ref={setActivatorNodeRef}
        className="drag-handle"
        {...attributes}
        {...listeners}
      >⠿</button>
      <div className="goal-card-info" onClick={() => onEdit(goal)}>
        <div className="goal-card-name">
          {goal.name}
          {goal.is_negative && <span className="negative-badge">avoid</span>}
          {!goal.enabled && <span className="paused-badge">paused</span>}
        </div>
        <div className="goal-card-meta">
          {typeof TYPE_LABEL[goal.type] === 'function' ? TYPE_LABEL[goal.type](goal) : TYPE_LABEL[goal.type]}
          {goal.reward_rules.length > 0 && ` · ${goal.reward_rules.length} reward rule${goal.reward_rules.length > 1 ? 's' : ''}`}
        </div>
      </div>
    </div>
  )
}

export default function GoalsPage({ goals, onAdd, onUpdate, onDelete, onSetEnabled, onReorder }) {
  const [showForm, setShowForm] = useState(false)
  const [editGoal, setEditGoal] = useState(null)
  const [confirmId, setConfirmId] = useState(null)
  const [localGoals, setLocalGoals] = useState(goals)

  useEffect(() => { setLocalGoals(goals) }, [goals])

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } }),
    useSensor(TouchSensor, { activationConstraint: { delay: 150, tolerance: 5 } }),
  )

  const handleDragEnd = async (event) => {
    const { active, over } = event
    if (!over || active.id === over.id) return
    const oldIndex = localGoals.findIndex(g => g.id === active.id)
    const newIndex = localGoals.findIndex(g => g.id === over.id)
    const reordered = arrayMove(localGoals, oldIndex, newIndex)
    setLocalGoals(reordered)
    await onReorder(reordered.map(g => g.id))
  }

  const handleSave = async (data) => {
    if (editGoal) {
      await onUpdate(editGoal.id, data)
    } else {
      await onAdd(data)
    }
    setShowForm(false)
    setEditGoal(null)
  }

  const handleDeleteRequest = (id) => {
    setShowForm(false)
    setEditGoal(null)
    setConfirmId(id)
  }

  const handleDeleteConfirmed = async () => {
    await onDelete(confirmId)
    setConfirmId(null)
  }

  return (
    <>
      {localGoals.length === 0 && (
        <div className="empty-state">
          No goals yet.<br />Tap the button below to add your first one.
        </div>
      )}

      <div className="goals-list">
        <DndContext sensors={sensors} collisionDetection={closestCenter} onDragEnd={handleDragEnd} modifiers={[restrictToVerticalAxis, restrictToParentElement]}>
          <SortableContext items={localGoals.map(g => g.id)} strategy={verticalListSortingStrategy}>
            {localGoals.map(goal => (
              <SortableGoalCard
                key={goal.id}
                goal={goal}
                onEdit={(g) => { setEditGoal(g); setShowForm(true) }}
              />
            ))}
          </SortableContext>
        </DndContext>
      </div>

      <button className="add-btn" onClick={() => { setEditGoal(null); setShowForm(true) }}>
        + New Goal
      </button>

      {showForm && (
        <GoalForm
          goal={editGoal}
          onSave={handleSave}
          onClose={() => { setShowForm(false); setEditGoal(null) }}
          onSetEnabled={onSetEnabled}
          onDelete={handleDeleteRequest}
        />
      )}

      {confirmId && (
        <ConfirmDialog
          message="Delete this goal? This cannot be undone."
          onConfirm={handleDeleteConfirmed}
          onCancel={() => setConfirmId(null)}
        />
      )}
    </>
  )
}
