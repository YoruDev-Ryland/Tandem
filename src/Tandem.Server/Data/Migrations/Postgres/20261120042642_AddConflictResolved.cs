using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Tandem.Server.Data.Migrations.Postgres
{
    /// <inheritdoc />
    public partial class AddConflictResolved : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<bool>(
                name: "Resolved",
                table: "SaveVersions",
                type: "boolean",
                nullable: false,
                defaultValue: false);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropColumn(
                name: "Resolved",
                table: "SaveVersions");
        }
    }
}
