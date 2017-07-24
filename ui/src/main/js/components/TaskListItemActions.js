import React from 'react';

export default function ({ task }) {
  return (<div className='task-list-item-host'>
    <a href={`http://${task.assignedTask.slaveHost}:5050/state.json`}>
      {task.assignedTask.slaveHost}
    </a>
  </div>);
}
