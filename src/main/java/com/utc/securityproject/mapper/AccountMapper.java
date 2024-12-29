package com.utc.securityproject.mapper;

import com.utc.securityproject.dto.response.AccountDTO;
import com.utc.securityproject.entity.Account;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface AccountMapper {
  AccountDTO toDTO(Account account);
  
}
