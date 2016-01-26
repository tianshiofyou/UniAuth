package com.dianrong.common.uniauth.common.bean.request;

import com.dianrong.common.uniauth.common.bean.dto.GroupDto;

public class GroupTreeParam extends Operator {
	//need further discussion
	//frontend: (1) group array (2) only pass incremental changes
	private GroupDto groupDto;
	private Integer roleId;

	public GroupDto getGroupDto() {
		return groupDto;
	}

	public GroupTreeParam setGroupDto(GroupDto groupDto) {
		this.groupDto = groupDto;
		return this;
	}

	public Integer getRoleId() {
		return roleId;
	}

	public GroupTreeParam setRoleId(Integer roleId) {
		this.roleId = roleId;
		return this;
	}
}
